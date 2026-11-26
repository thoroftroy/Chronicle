package dev.wonderdesk.chronicle

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.time.temporal.ChronoUnit

private val zoneId: ZoneId get() = ZoneId.systemDefault()

/** One task on one day: a one-off task has a single day, a repeating one has an occurrence for every day it repeats. */
data class TaskOcc(val task: Task, val day: LocalDate, val done: Boolean, val checked: Set<String>)

/** What a repeat means, how a day's completion is worked out, and the icons that mark good days. */
object TaskLogic {
    val REPEATS = listOf("" to "Never", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly", "monthly" to "Monthly", "yearly" to "Yearly")

    fun dueDay(t: Task): LocalDate? = when {
        t.due <= 0 -> null
        t.dueHasTime -> Instant.ofEpochMilli(t.due).atZone(zoneId).toLocalDate()
        else -> LocalDate.ofEpochDay(t.due / 86_400_000L)
    }

    fun dueTime(t: Task): LocalTime? = if (t.dueHasTime && t.due > 0) Instant.ofEpochMilli(t.due).atZone(zoneId).toLocalTime() else null

    fun repeats(t: Task) = t.repeat.isNotBlank()

    private fun anchorDay(t: Task): LocalDate = when {
        t.anchor > 0 -> LocalDate.ofEpochDay(t.anchor)
        else -> dueDay(t) ?: Instant.ofEpochMilli(t.created).atZone(zoneId).toLocalDate()
    }

    /** Does this task come up on [day]? */
    fun scheduledOn(t: Task, day: LocalDate): Boolean {
        if (!repeats(t)) return dueDay(t) == day
        val a = anchorDay(t)
        if (day.isBefore(a)) return false
        val n = t.every.coerceAtLeast(1)
        return when (t.repeat) {
            "daily" -> ChronoUnit.DAYS.between(a, day) % n == 0L
            "weekdays" -> day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY
            "weekly" -> {
                val mask = if (t.days != 0) t.days else 1 shl (a.dayOfWeek.value - 1)
                val weeks = ChronoUnit.WEEKS.between(a.with(DayOfWeek.MONDAY), day.with(DayOfWeek.MONDAY))
                (mask shr (day.dayOfWeek.value - 1)) and 1 == 1 && weeks % n == 0L
            }
            "monthly" -> day.dayOfMonth == minOf(a.dayOfMonth, day.lengthOfMonth()) && ChronoUnit.MONTHS.between(a.withDayOfMonth(1), day.withDayOfMonth(1)) % n == 0L
            "yearly" -> day.monthValue == a.monthValue && day.dayOfMonth == minOf(a.dayOfMonth, day.lengthOfMonth()) && (day.year - a.year) % n == 0
            else -> false
        }
    }

    fun doneOn(t: Task, day: LocalDate): Boolean = if (repeats(t)) day.toEpochDay() in t.history else t.done

    fun checkedOn(t: Task, day: LocalDate): Set<String> = if (repeats(t)) (t.checks[day.toEpochDay()] ?: emptyList()).toSet() else t.checklist.filter { it.done }.map { it.id }.toSet()

    fun occ(t: Task, day: LocalDate) = TaskOcc(t, day, doneOn(t, day), checkedOn(t, day))

    /** Tasks that come up on [day], including repeating ones. */
    fun on(tasks: List<Task>, day: LocalDate): List<TaskOcc> = tasks.filter { it.title.isNotBlank() && scheduledOn(it, day) && (repeats(it) || !it.done || dueDay(it) == day) }.map { occ(it, day) }

    /** The next day a task needs doing: today if it is on today's list and not finished, else the next scheduled day. Null for one-offs without a date or already finished. */
    fun nextDay(t: Task, from: LocalDate = LocalDate.now()): LocalDate? {
        if (!repeats(t)) return if (t.done) null else dueDay(t)
        var d = from
        for (i in 0..800) { if (scheduledOn(t, d) && !(i == 0 && doneOn(t, d))) return d; d = d.plusDays(1) }
        return null
    }

    /** When the next reminder should ring, in epoch millis (9:00 for tasks without a time), or 0. */
    fun nextAlarm(t: Task, after: Long = System.currentTimeMillis()): Long = nextReminder(t, after)?.at ?: 0L

    /** One reminder: when it rings, for which day's round of the task, and what kind it is. */
    data class Reminder(val at: Long, val day: LocalDate, val kind: String, val minutes: Int)

    /** Nags stay quiet from 23:00 to 7:00 and pick up again in the morning. */
    private fun quiet(at: Long): Boolean = Instant.ofEpochMilli(at).atZone(zoneId).hour.let { it >= 23 || it < 7 }

    /**
     * The next reminder after [after]: the ones set before the due time, the due time itself, "Later" from a notification,
     * and with [Task.nag] a repeat every so often until it is ticked off (for a repeating task, until its next round; for a one-off, up to a week).
     */
    fun nextReminder(t: Task, after: Long = System.currentTimeMillis()): Reminder? {
        if (t.title.isBlank()) return null
        val time = dueTime(t) ?: LocalTime.of(9, 0)
        val today = Instant.ofEpochMilli(after).atZone(zoneId).toLocalDate()
        val found = ArrayList<Reminder>()
        if (t.later > after && !(t.done && !repeats(t))) found += Reminder(t.later, nextDay(t, today) ?: today, "later", 0)
        // Rounds still to do: an overdue one-off, today's (if not finished), and the next few, since "a day before" rings before its round.
        val rounds = ArrayList<LocalDate>()
        if (!repeats(t)) { if (!t.done) dueDay(t)?.let { rounds += it } }
        else {
            var d: LocalDate? = nextDay(t, today.minusDays(1))
            while (d != null && rounds.size < 4) { if (!doneOn(t, d)) rounds += d; d = nextDay(t, d.plusDays(1)) }
        }
        rounds.forEachIndexed { i, d ->
            val due = d.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
            t.remind.forEach { m -> val at = due - m * 60_000L; if (at > after) found += Reminder(at, d, if (m == 0) "due" else "before", m) }
            if (t.nag > 0) {
                val until = if (repeats(t)) rounds.getOrNull(i + 1)?.atTime(time)?.atZone(zoneId)?.toInstant()?.toEpochMilli() ?: (due + 86_400_000L) else due + 7 * 86_400_000L
                val step = t.nag * 60_000L
                var at = if (after <= due) due + step else due + ((after - due) / step + 1) * step
                while (at < until && quiet(at)) at += step
                if (at < until) found += Reminder(at, d, "nag", ((at - due) / 60_000L).toInt())
            }
        }
        return found.minByOrNull { it.at }
    }

    /** "15 minutes", "2 hours", "1 day", "1 week". */
    fun span(minutes: Int): String {
        fun u(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
        return when {
            minutes % 10080 == 0 -> u(minutes / 10080, "week")
            minutes % 1440 == 0 -> u(minutes / 1440, "day")
            minutes % 60 == 0 -> u(minutes / 60, "hour")
            minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
            else -> u(minutes, "minute")
        }
    }

    /** Words for the editor and the task list: "At the time, 1 day before · then every 30 minutes until done". */
    fun reminderLabel(t: Task): String {
        if (t.remind.isEmpty() && t.nag == 0) return "No reminders"
        val before = t.remind.sorted().joinToString(", ") { if (it == 0) "at the time" else span(it) + " before" }.replaceFirstChar { it.uppercase() }
        val nag = if (t.nag > 0) "every ${span(t.nag)} until done" else ""
        return listOf(before, nag).filter { it.isNotBlank() }.joinToString(" · then ").replaceFirstChar { it.uppercase() }
    }

    /** Marks a day done or not done. */
    fun setDone(t: Task, day: LocalDate, done: Boolean, now: Long = System.currentTimeMillis()): Task {
        val e = day.toEpochDay()
        if (!repeats(t)) return if (done) t.copy(done = true, completedAt = now, checklist = t.checklist.map { it.copy(done = true) }) else t.copy(done = false, completedAt = 0L)
        val hist = if (done) (t.history + e).distinct().sorted() else t.history - e
        val ch = if (done) t.checks + (e to t.checklist.map { it.id }) else t.checks - e
        return t.copy(history = hist, checks = ch, completedAt = if (done) now else t.completedAt)
    }

    /** Ticks one checklist item; finishing the last one completes the day, and un-ticking reopens it. */
    fun toggleCheck(t: Task, day: LocalDate, itemId: String): Task {
        val e = day.toEpochDay()
        val ticked = checkedOn(t, day).toMutableSet()
        if (!ticked.add(itemId)) ticked.remove(itemId)
        var r = if (repeats(t)) t.copy(checks = t.checks + (e to ticked.toList())) else t.copy(checklist = t.checklist.map { it.copy(done = it.id in ticked) })
        val all = r.checklist.isNotEmpty() && r.checklist.all { it.id in ticked }
        val wasDone = doneOn(r, day)
        if (all && !wasDone) r = setDone(r, day, true)
        else if (!all && wasDone) r = if (repeats(r)) r.copy(history = r.history - e) else r.copy(done = false, completedAt = 0L)
        return r
    }

    /** How a day went: tasks that came up and how many were finished. */
    fun progress(tasks: List<Task>, day: LocalDate): Pair<Int, Int> {
        val list = tasks.filter { it.title.isNotBlank() && scheduledOn(it, day) }
        return list.count { doneOn(it, day) } to list.size
    }

    fun repeatLabel(t: Task): String {
        if (!repeats(t)) return ""
        val n = t.every.coerceAtLeast(1)
        return when (t.repeat) {
            "daily" -> if (n == 1) "Every day" else "Every $n days"
            "weekdays" -> "Weekdays"
            "weekly" -> {
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                val sel = (0..6).filter { (t.days shr it) and 1 == 1 }.map { names[it] }
                (if (n == 1) "Every week" else "Every $n weeks") + if (sel.isNotEmpty()) " on " + sel.joinToString(", ") else ""
            }
            "monthly" -> if (n == 1) "Every month" else "Every $n months"
            "yearly" -> if (n == 1) "Every year" else "Every $n years"
            else -> ""
        }
    }
}

/** Icons on days: ones you place yourself, and an automatic one for days when every task was finished. */
object DayMarks {
    /** Bumped whenever an icon changes, so screens showing icons redraw. */
    var rev by androidx.compose.runtime.mutableIntStateOf(0)
    val ICONS = listOf("⭐", "🌟", "❤️", "💙", "🎉", "🏆", "🔥", "💪", "✅", "🌈", "😀", "😴", "🎂", "✈️")
    private fun prefs(c: Context) = c.getSharedPreferences("chronicle-marks", Context.MODE_PRIVATE)

    fun manual(c: Context): Map<Long, String> = runCatching { JSONObject(prefs(c).getString("marks", "{}")!!).let { o -> o.keys().asSequence().associate { it.toLong() to o.getString(it) } } }.getOrDefault(emptyMap())
    fun setManual(c: Context, day: LocalDate, icon: String?) {
        val m = manual(c).toMutableMap(); if (icon == null) m.remove(day.toEpochDay()) else m[day.toEpochDay()] = icon
        prefs(c).edit().putString("marks", JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v) } }.toString()).apply()
        rev++
    }

    fun rewardOn(c: Context) = prefs(c).getBoolean("reward", true)
    fun setRewardOn(c: Context, on: Boolean) { prefs(c).edit().putBoolean("reward", on).apply(); rev++ }
    fun rewardIcon(c: Context) = prefs(c).getString("rewardIcon", "⭐") ?: "⭐"
    fun setRewardIcon(c: Context, icon: String) { prefs(c).edit().putString("rewardIcon", icon).apply(); rev++ }

    /** The icon for [day]: yours if you placed one, otherwise the reward if all of that day's tasks are done. */
    fun iconFor(c: Context, marks: Map<Long, String>, tasks: List<Task>, day: LocalDate, rewardOn: Boolean, reward: String): String? {
        marks[day.toEpochDay()]?.let { return it }
        if (!rewardOn || day.isAfter(LocalDate.now())) return null
        val (done, total) = TaskLogic.progress(tasks, day)
        return if (total > 0 && done == total) reward else null
    }
}
