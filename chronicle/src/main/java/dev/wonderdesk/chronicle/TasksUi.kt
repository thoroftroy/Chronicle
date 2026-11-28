package dev.wonderdesk.chronicle

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wonderdesk.shared.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val tz: ZoneId get() = ZoneId.systemDefault()
private fun clock(ms: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
private fun dayText(d: LocalDate, today: LocalDate) = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM")) }

/** A task on a day, ready to show: which day it stands for and where it sits in the lists. */
private data class Entry(val occ: TaskOcc, val overdue: Boolean = false)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksView(tasks: MutableList<Task>, save: () -> Unit, openRequest: MutableState<Task?> = mutableStateOf(null)) {
    val context = LocalContext.current
    val p = wonderPalette()
    var showDone by rememberSaveable { mutableStateOf(false) }
    var byGroup by rememberSaveable { mutableStateOf(context.prefs("chronicle").getBoolean("tasksByGroup", false)) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var iconFor by remember { mutableStateOf<LocalDate?>(null) }
    val expanded = remember { mutableStateListOf<String>() }
    LaunchedEffect(openRequest.value) { openRequest.value?.let { editing = it; openRequest.value = null } }
    LaunchedEffect(tasks.firstOrNull()?.id) { tasks.firstOrNull()?.let { if (it.title.isEmpty()) editing = it } }
    val today = LocalDate.now()

    fun replace(n: Task) { val i = tasks.indexOfFirst { it.id == n.id }; if (i >= 0) { tasks[i] = n; save() } }
    fun complete(o: TaskOcc, on: Boolean) { replace(TaskLogic.setDone(o.task, o.day, on)); if (on) { WonderMood.celebrate(TASK_DONE_GLYPHS); SceneFeed.beat() } }
    fun tick(o: TaskOcc, item: String) = replace(TaskLogic.toggleCheck(o.task, o.day, item))

    val real = tasks.filter { it.title.isNotEmpty() }
    // Today's list: everything that comes up today, finished or not. Everything else by when it is next due.
    val todayAll = TaskLogic.on(real, today)
    val todayOpen = todayAll.filter { !it.done }
    // A calendar page drifts in the scene for each task still to do today (and the overdue ones); finishing one stamps a page with a tick
    // and it falls away. A tide shows how much of today is done.
    val leftToday = todayOpen.size + real.count { !TaskLogic.repeats(it) && !it.done && (TaskLogic.dueDay(it)?.isBefore(today) == true) }
    SceneFeedScope()
    SideEffect { SceneFeed.count = leftToday.coerceAtMost(14) }
    SceneMoodEffect(SceneMood(level = if (todayAll.isEmpty()) null else (todayAll.size - todayOpen.size).toFloat() / todayAll.size, levelHue = 135f, glyphs = listOf("✓", "•", "·")))
    val overdue = real.filter { !TaskLogic.repeats(it) && !it.done && (TaskLogic.dueDay(it)?.isBefore(today) == true) }.map { TaskLogic.occ(it, TaskLogic.dueDay(it)!!) }
    val upcoming = real.filter { t -> !(TaskLogic.repeats(t) && TaskLogic.scheduledOn(t, today)) && !t.done }.mapNotNull { t -> TaskLogic.nextDay(t, today.plusDays(1))?.takeIf { it.isAfter(today) }?.let { TaskLogic.occ(t, it) } }
        .filter { !(it.task.done) }.sortedBy { it.day.toEpochDay() }
    val someday = real.filter { !TaskLogic.repeats(it) && !it.done && it.due == 0L }.map { TaskLogic.occ(it, today) }
    val finished = real.filter { !TaskLogic.repeats(it) && it.done }.sortedByDescending { it.completedAt }.map { TaskLogic.occ(it, TaskLogic.dueDay(it) ?: today) }
    val doneToday = todayAll.filter { it.done && (TaskLogic.repeats(it.task) || it.task.completedAt > 0) }

    val (doneCount, totalCount) = TaskLogic.progress(real, today)
    val marks = remember(tasks.toList(), doneCount) { DayMarks.manual(context) }
    val icon = DayMarks.iconFor(context, marks, real, today, DayMarks.rewardOn(context), DayMarks.rewardIcon(context))

    if (real.isEmpty() && editing == null) {
        EmptyState("No tasks", "Keep a to-do list with deadlines, repeats, groups and checklists. Tasks also show up in your month view and widget.", icon = WonderIcons.CheckCircle,
            action = { WButton("New task", { tasks.add(0, Task()); editing = tasks[0] }, icon = WonderIcons.Add, kind = ButtonKind.Primary) })
    } else LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp)) {
        item {
            Panel {
                Column(Modifier.clickable { iconFor = today }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (totalCount == 0) "Nothing due" else "$doneCount of $totalCount done", style = MaterialTheme.typography.labelLarge, color = if (totalCount > 0 && doneCount == totalCount) p.success else p.secondaryText)
                        Spacer(Modifier.width(8.dp))
                        Text(icon ?: "☆", fontSize = 24.sp, color = if (icon == null) p.secondaryText.copy(alpha = .5f) else Color.Unspecified)
                    }
                    if (totalCount > 0) AquaProgress(doneCount.toFloat() / totalCount, Modifier.fillMaxWidth(), tint = if (doneCount == totalCount) p.success else null)
                }
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth()) { Segmented(listOf("By date", "By group"), if (byGroup) 1 else 0, Modifier.fillMaxWidth()) { byGroup = it == 1; context.prefs("chronicle").edit().putBoolean("tasksByGroup", byGroup).apply() } }
        }
        val sections: List<Pair<String, List<TaskOcc>>> = if (!byGroup) listOf(
            "Overdue" to overdue, "Today" to todayOpen, "Upcoming" to upcoming, "Someday" to someday,
        ) else {
            val open = overdue + todayOpen + upcoming + someday
            open.groupBy { it.task.list.trim() }.entries.sortedWith(compareBy({ it.key.isEmpty() }, { it.key.lowercase() })).map { (g, l) -> (g.ifEmpty { "No group" }) to l.sortedWith(compareBy({ it.day.toEpochDay() }, { -it.task.priority })) }
        }
        sections.filter { it.second.isNotEmpty() }.forEach { (name, list) ->
            item(key = "h-$name") { SectionLabel(if (byGroup) "$name · ${list.size}" else name, Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp)) }
            itemsIndexed(if (byGroup) list else list.sortedByDescending { it.task.priority }, key = { _, o -> "${name}-${o.task.id}-${o.day.toEpochDay()}" }) { i, o ->
                val r = p.panelRadius
                Box(Modifier.animateItemPlacement()) {
                    SwipeActions(start = SwipeAction("Done", WonderIcons.CheckCircle, Color(0xFF2E9E44), destructive = true) { complete(o, true) },
                        end = if (TaskLogic.repeats(o.task)) null else SwipeAction("Tomorrow", WonderIcons.Schedule, Color(0xFF1E7FE8)) {
                            val t = o.task; val base = LocalDate.now().plusDays(1)
                            replace(t.copy(due = if (t.dueHasTime) base.atTime(TaskLogic.dueTime(t)!!).atZone(tz).toInstant().toEpochMilli() else base.toEpochDay() * 86_400_000L))
                        },
                        modifier = Modifier.clip(RoundedCornerShape(if (i == 0) r else 0.dp, if (i == 0) r else 0.dp, if (i == list.size - 1) r else 0.dp, if (i == list.size - 1) r else 0.dp))) {
                        TaskRow(o, Modifier.panelItem(i, list.size), overdue = name == "Overdue", showDay = byGroup || name == "Upcoming", expanded = o.task.id in expanded,
                            onToggle = { complete(o, !o.done) }, onTick = { tick(o, it) },
                            onExpand = { if (o.task.id in expanded) expanded.remove(o.task.id) else expanded.add(o.task.id) }, onClick = { editing = o.task })
                    }
                }
            }
        }
        if (doneToday.isNotEmpty() || finished.isNotEmpty()) {
            item { Row(Modifier.padding(top = 14.dp)) { WButton(if (showDone) "Hide completed" else "Show completed (${doneToday.size + finished.size})", { showDone = !showDone }, kind = ButtonKind.Plain) } }
            if (showDone) {
                val all = doneToday + finished.filter { f -> doneToday.none { it.task.id == f.task.id } }
                itemsIndexed(all, key = { _, o -> "d-${o.task.id}-${o.day.toEpochDay()}" }) { i, o ->
                    TaskRow(o, Modifier.panelItem(i, all.size), overdue = false, showDay = false, expanded = o.task.id in expanded, onToggle = { complete(o, false) }, onTick = { tick(o, it) },
                        onExpand = { if (o.task.id in expanded) expanded.remove(o.task.id) else expanded.add(o.task.id) }, onClick = { editing = o.task })
                }
            }
        }
    }
    editing?.let { t ->
        val groups = real.map { it.list.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        TaskEditor(t, groups, onDismiss = {
            if (t.title.isEmpty()) tasks.removeAll { it.id == t.id && it.title.isEmpty() }
            editing = null
        }, onSave = { n -> val i = tasks.indexOfFirst { it.id == n.id }; if (i >= 0) tasks[i] = n else tasks.add(0, n); save(); editing = null },
            onDelete = { tasks.removeAll { it.id == t.id }; save(); editing = null })
    }
    iconFor?.let { d -> DayIconDialog(d, onDismiss = { iconFor = null }) }
}

@Composable
private fun TaskRow(o: TaskOcc, modifier: Modifier, overdue: Boolean, showDay: Boolean, expanded: Boolean, onToggle: () -> Unit, onTick: (String) -> Unit, onExpand: () -> Unit, onClick: () -> Unit) {
    val p = wonderPalette()
    val t = o.task
    val today = LocalDate.now()
    Column(modifier) {
        Row(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (o.done) p.success else if (t.priority > 0) p.danger else p.panelBorder, CircleShape)
                .background(if (o.done) p.success else Color.Transparent).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                if (o.done) Icon(WonderIcons.Check, "Done", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (o.done) p.secondaryText else p.text,
                    textDecoration = if (o.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                val dayPart = if (showDay || TaskLogic.dueDay(t) != null && !TaskLogic.repeats(t)) {
                    dayText(o.day, today) + (TaskLogic.dueTime(t)?.let { " " + clock(t.due) } ?: "")
                } else TaskLogic.dueTime(t)?.let { clock(t.due) } ?: ""
                val bits = listOf(dayPart, TaskLogic.repeatLabel(t).let { if (it.isNotEmpty()) "↻ $it" else "" }, if (t.nag > 0 && !TaskLogic.doneOn(t, o.day)) "🔔 every " + TaskLogic.span(t.nag) else "", t.list.takeIf { it.isNotBlank() && showDay } ?: "", t.notes.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (overdue) p.danger else p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.checklist.isNotEmpty()) {
                val n = t.checklist.count { it.id in o.checked }
                Row(Modifier.clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = .14f)).clickable(onClick = onExpand).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$n/${t.checklist.size}", style = MaterialTheme.typography.labelMedium, color = p.accent)
                    Icon(if (expanded) WonderIcons.ExpandLess else WonderIcons.ExpandMore, null, tint = p.accent, modifier = Modifier.size(16.dp))
                }
            }
            if (t.priority > 0) { Spacer(Modifier.width(6.dp)); Icon(WonderIcons.Flag, "Important", tint = p.danger, modifier = Modifier.size(18.dp)) }
        }
        if (expanded && t.checklist.isNotEmpty()) Column(Modifier.padding(start = 50.dp, end = 12.dp, bottom = 8.dp)) {
            t.checklist.forEach { c ->
                val on = c.id in o.checked
                Row(Modifier.fillMaxWidth().clickable { onTick(c.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { onTick(c.id) })
                    Text(c.text, color = if (on) p.secondaryText else p.text, textDecoration = if (on) androidx.compose.ui.text.style.TextDecoration.LineThrough else null, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Pick an icon for a day (or clear it). The automatic reward shows on its own when every task for the day is finished. */
@Composable
fun DayIconDialog(day: LocalDate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var current by remember { mutableStateOf(DayMarks.manual(context)[day.toEpochDay()]) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Icon for ${dayText(day, LocalDate.now())}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Put a star, a heart or anything else on this day. Days where every task is finished get ${DayMarks.rewardIcon(context)} by themselves (change that in Settings).", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            DayMarks.ICONS.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { ic ->
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(if (current == ic) p.accent.copy(alpha = .25f) else p.panel).border(1.dp, if (current == ic) p.accent else p.panelBorder, RoundedCornerShape(12.dp))
                            .clickable { current = ic; DayMarks.setManual(context, day, ic) }, contentAlignment = Alignment.Center) { Text(ic, fontSize = 24.sp) }
                    }
                }
            }
            if (current != null) WButton("Remove the icon", { DayMarks.setManual(context, day, null); current = null }, kind = ButtonKind.Plain, icon = WonderIcons.Close)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
fun TaskSettings() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(DayMarks.rewardOn(context)) }
    var icon by remember { mutableStateOf(DayMarks.rewardIcon(context)) }
    Panel(title = "Tasks", footer = "Days where every task that came up was finished get this icon in the month view and in your task list.") {
        SwitchRow("Reward icon for finished days", on, { on = it; DayMarks.setRewardOn(context, it) }, icon = WonderIcons.CheckCircle, tint = Color(0xFFF2A516), help = "When you finish every task due on a day, that day gets a small star in the month view. Just a little pat on the back.")
        if (on) {
            PanelDivider()
            Row(Modifier.padding(16.dp, 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayMarks.ICONS.forEach { ic -> Chip(ic, icon == ic, { icon = ic; DayMarks.setRewardIcon(context, ic) }) }
            }
        }
    }
}

@Composable
fun TaskEditor(t: Task, groups: List<String>, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: () -> Unit) {
    val p = wonderPalette()
    var title by remember { mutableStateOf(t.title) }
    var notes by remember { mutableStateOf(t.notes) }
    var due by remember { mutableLongStateOf(t.due) }
    var hasTime by remember { mutableStateOf(t.dueHasTime) }
    var important by remember { mutableStateOf(t.priority > 0) }
    var group by remember { mutableStateOf(t.list) }
    var repeat by remember { mutableStateOf(t.repeat) }
    var every by remember { mutableIntStateOf(t.every.coerceAtLeast(1)) }
    var days by remember { mutableIntStateOf(t.days) }
    var asEvent by remember { mutableStateOf(t.asEvent) }
    var minutes by remember { mutableIntStateOf(t.minutes) }
    val remind = remember { mutableStateListOf<Int>().apply { addAll(t.remind) } }
    var nag by remember { mutableIntStateOf(t.nag) }
    var custom by remember { mutableStateOf<String?>(null) }   // "before" or "nag" while the custom amount dialog is open
    val items = remember { mutableStateListOf<CheckItem>().apply { addAll(t.checklist) } }
    var newItem by remember { mutableStateOf("") }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    fun dayOf(): LocalDate? = if (due <= 0) null else if (hasTime) Instant.ofEpochMilli(due).atZone(tz).toLocalDate() else LocalDate.ofEpochDay(due / 86_400_000L)
    fun setDay(d: LocalDate?) { due = if (d == null) 0L else if (hasTime) d.atTime(Instant.ofEpochMilli(due.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(tz).toLocalTime()).atZone(tz).toInstant().toEpochMilli() else d.toEpochDay() * 86_400_000L }
    fun addItem() { if (newItem.isNotBlank()) { items.add(CheckItem(text = newItem.trim())); newItem = "" } }

    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (t.title.isEmpty()) "New task" else "Edit task") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(title, { title = it }, "Task")
            Field(notes, { notes = it }, "Notes", singleLine = false, minLines = 2)

            Text("Deadline", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dd = dayOf()
                Chip("None", due == 0L, { setDay(null); hasTime = false; asEvent = false })
                Chip("Today", dd == today, { setDay(today) }); Chip("Tomorrow", dd == today.plusDays(1), { setDay(today.plusDays(1)) })
                Chip("Next week", dd == today.plusWeeks(1), { setDay(today.plusWeeks(1)) })
                Chip(if (dd != null && dd !in listOf(today, today.plusDays(1), today.plusWeeks(1))) dd.format(DateTimeFormatter.ofPattern("d MMM")) else "Pick…", false, { pickDate = true }, WonderIcons.Event)
            }
            if (due > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(hasTime, { on ->
                    val d = dayOf() ?: today
                    hasTime = on
                    due = if (on) d.atTime(9, 0).atZone(tz).toInstant().toEpochMilli() else d.toEpochDay() * 86_400_000L
                    if (!on) asEvent = false
                })
                Text("At a time" + if (hasTime) " · " + clock(due) else "", Modifier.clickable(enabled = hasTime) { pickTime = true })
            }

            if (due > 0) {
                Text("Reminders", style = MaterialTheme.typography.titleSmall)
                Text(if (hasTime) "How long before it is due. Pick as many as you like." else "Tasks without a time count from 9:00. Pick as many as you like.",
                    style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(0, 5, 15, 30, 60, 120, 1440, 10080)
                    (presets + remind.filter { it !in presets }.sorted()).forEach { m ->
                        Chip(if (m == 0) "At the time" else TaskLogic.span(m).replace(" minutes", " min").replace(" minute", " min"), m in remind,
                            { if (m in remind) remind.remove(m) else remind.add(m) })
                    }
                    Chip("Custom…", false, { custom = "before" }, WonderIcons.Add)
                }
                Text("Keep reminding until it's done", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(15, 30, 60, 120, 240)
                    Chip("Off", nag == 0, { nag = 0 })
                    (presets + listOfNotNull(nag.takeIf { it > 0 && it !in presets })).forEach { m -> Chip("Every " + TaskLogic.span(m).replace(" minutes", " min"), nag == m, { nag = m }) }
                    Chip("Custom…", false, { custom = "nag" }, WonderIcons.Add)
                }
                Text(if (nag > 0) "After it is due, a reminder every ${TaskLogic.span(nag)} until you tick it off. Quiet from 11 PM to 7 AM." else "Each reminder has Done and Later buttons.",
                    style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }

            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TaskLogic.REPEATS.forEach { (k, l) -> Chip(l, repeat == k, { repeat = k; if (k != "" && due == 0L) setDay(today) }) }
            }
            if (repeat in listOf("daily", "weekly", "monthly", "yearly")) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Every", style = MaterialTheme.typography.bodyMedium)
                WButton("−", { if (every > 1) every-- }, kind = ButtonKind.Plain)
                Text("$every " + when (repeat) { "daily" -> "day"; "weekly" -> "week"; "monthly" -> "month"; else -> "year" } + if (every > 1) "s" else "", style = MaterialTheme.typography.titleSmall)
                WButton("+", { if (every < 30) every++ }, kind = ButtonKind.Plain)
            }
            if (repeat == "weekly") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, n -> Chip(n, (days shr i) and 1 == 1, { days = days xor (1 shl i) }) }
            }

            Text("Group", style = MaterialTheme.typography.titleSmall)
            Field(group, { group = it }, "Group (like Work or Closing duties)")
            if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { g -> Chip(g, group.equals(g, true), { group = g }) }
            }

            Text("Checklist", style = MaterialTheme.typography.titleSmall)
            Text(if (repeat.isNotEmpty()) "Ticked fresh every time the task comes round. Tick them all and the day is done." else "Tick them all and the task is done.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            items.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("•", Modifier.padding(end = 8.dp), color = p.accent)
                    Text(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { if (i > 0) { val x = items.removeAt(i); items.add(i - 1, x) } }, enabled = i > 0) { Icon(WonderIcons.ExpandLess, "Move up", modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { items.removeAt(i) }) { Icon(WonderIcons.Close, "Remove", modifier = Modifier.size(18.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(newItem, { newItem = it }, "Add an item", onDone = { addItem() }) }
                WButton("Add", { addItem() }, kind = ButtonKind.Plain, icon = WonderIcons.Add)
            }

            if (hasTime && due > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(asEvent, { asEvent = it }); Text("Show on the calendar like an event") }
                if (asEvent) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours").forEach { (m, l) -> Chip(l, minutes == m, { minutes = m }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(important, { important = it }); Text("Important") }
            if (t.title.isNotEmpty()) WButton("Delete task", onDelete, icon = WonderIcons.Delete, kind = ButtonKind.Plain)
        }
    }, confirmButton = {
        TextButton(enabled = title.isNotBlank(), onClick = {
            addItem()
            val anchor = if (repeat.isNotEmpty()) (dayOf() ?: today).toEpochDay() else 0L
            // Changing what repeats starts a new run of history; just editing words keeps it.
            val keep = repeat == t.repeat && anchor == (if (t.anchor > 0) t.anchor else anchor)
            onSave(t.copy(title = title.trim(), notes = notes.trim(), due = due, dueHasTime = hasTime && due > 0, priority = if (important) 1 else 0, list = group.trim(),
                checklist = items.toList().let { l -> if (repeat.isNotEmpty()) l.map { it.copy(done = false) } else l },
                repeat = repeat, every = every, days = if (repeat == "weekly") days else 0, anchor = if (keep && t.anchor > 0) t.anchor else anchor,
                history = if (keep) t.history else emptyList(), checks = if (keep) t.checks else emptyMap(), asEvent = asEvent && hasTime && due > 0, minutes = minutes,
                remind = remind.distinct().sorted(), nag = nag, later = if (due != t.due) 0L else t.later,
                done = if (repeat.isNotEmpty()) false else t.done))
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    custom?.let { what ->
        var amount by remember { mutableStateOf("") }
        var unit by remember { mutableIntStateOf(if (what == "nag") 0 else 1) }
        val mins = (amount.toIntOrNull() ?: 0) * listOf(1, 60, 1440)[unit]
        WonderDialog(onDismissRequest = { custom = null }, title = { Text(if (what == "nag") "Remind again every" else "Remind me before it's due") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(amount, { v -> amount = v.filter { it.isDigit() }.take(4) }, "How many", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                Segmented(listOf("Minutes", "Hours", "Days"), unit) { unit = it }
                if (mins > 0) Text(if (what == "nag") "Every ${TaskLogic.span(mins)} until done" else "${TaskLogic.span(mins)} before", color = p.secondaryText, style = MaterialTheme.typography.bodyMedium)
            }
        }, confirmButton = {
            TextButton(enabled = mins in 1..(60 * 24 * 60), onClick = { if (what == "nag") nag = mins else if (mins !in remind) remind.add(mins); custom = null }) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { custom = null }) { Text("Cancel") } })
    }
    @OptIn(ExperimentalMaterial3Api::class)
    if (pickDate) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = dayOf()?.toEpochDay()?.times(86_400_000L))
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = { TextButton(onClick = { ds.selectedDateMillis?.let { setDay(LocalDate.ofEpochDay(it / 86_400_000L)) }; pickDate = false }) { Text("OK") } }) { DatePicker(ds) }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    if (pickTime) {
        val cur = LocalDateTime.ofInstant(Instant.ofEpochMilli(due), tz)
        val ts = rememberTimePickerState(cur.hour, cur.minute)
        WonderDialog(onDismissRequest = { pickTime = false }, title = { Text("Due time") }, text = { TimePicker(ts) },
            confirmButton = { TextButton(onClick = { due = cur.toLocalDate().atTime(ts.hour, ts.minute).atZone(tz).toInstant().toEpochMilli(); pickTime = false }) { Text("OK") } })
    }
}
