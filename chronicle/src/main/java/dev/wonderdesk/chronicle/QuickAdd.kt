package dev.wonderdesk.chronicle

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

/**
 * Understands an event typed in plain words: "Lunch with Sam tomorrow at 1pm", "Dentist friday 9:30 for 45 min",
 * "Trip 12 Dec all day", "Standup every weekday" is not supported (repeats are set in the editor).
 */
object QuickAdd {
    data class Parsed(val title: String, val day: LocalDate, val time: LocalTime?, val minutes: Int, val allDay: Boolean)

    fun parse(input: String, today: LocalDate = LocalDate.now()): Parsed {
        var text = " " + input.trim() + " "
        fun take(r: Regex): MatchResult? = r.find(text)?.also { text = text.replaceRange(it.range, " ") }
        var day = today
        var time: LocalTime? = null
        var minutes = 60
        var allDay = false
        val I = RegexOption.IGNORE_CASE
        if (take(Regex("\\s(all day|all-day)\\s", I)) != null) allDay = true
        take(Regex("\\sfor (\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hours?|m|min|mins|minutes?)\\s", I))?.let { m ->
            val n = m.groupValues[1].toDouble(); minutes = if (m.groupValues[2].lowercase().startsWith("h")) (n * 60).toInt() else n.toInt()
        }
        // Time ranges "3-4pm", "15:00-16:30", single times "at 3pm", "9:30", "noon".
        take(Regex("\\s(?:at |from )?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(?:-|–|to)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s", I))?.let { m ->
            val endAp = m.groupValues[6].lowercase(); val startAp = m.groupValues[3].lowercase().ifEmpty { endAp }
            val s = at(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, startAp); val e = at(m.groupValues[4].toInt(), m.groupValues[5].toIntOrNull() ?: 0, endAp)
            if (s != null && e != null) { time = s; minutes = ((e.toSecondOfDay() - s.toSecondOfDay()) / 60).let { if (it <= 0) it + 1440 else it } }
        }
        if (time == null) take(Regex("\\s(?:at )?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s", I))?.let { m -> time = at(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].lowercase()) }
        if (time == null) take(Regex("\\s(?:at )(\\d{1,2})(?::(\\d{2}))?\\s", I))?.let { m -> time = at(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, "") }
        if (time == null) take(Regex("\\s(\\d{1,2}):(\\d{2})\\s"))?.let { m -> time = at(m.groupValues[1].toInt(), m.groupValues[2].toInt(), "") }
        if (time == null) take(Regex("\\s(?:at )?(noon|midday)\\s", I))?.let { time = LocalTime.NOON }
        if (time == null) take(Regex("\\s(?:at )?midnight\\s", I))?.let { time = LocalTime.MIDNIGHT }
        // Days.
        when {
            take(Regex("\\stoday\\s|\\stonight\\s", I))?.also { if (it.value.trim().equals("tonight", true) && time == null) time = LocalTime.of(19, 0) } != null -> day = today
            take(Regex("\\stomorrow\\s|\\stmrw?\\s", I)) != null -> day = today.plusDays(1)
            else -> {
                take(Regex("\\s(next |this |on )?(mon|tues?|wed(?:nes)?|thu(?:rs)?|fri|sat(?:ur)?|sun)(?:day)?\\s", I))?.let { m ->
                    val dow = DayOfWeek.entries.first { it.name.lowercase().startsWith(m.groupValues[2].lowercase().take(3)) }
                    var d = today.plusDays(((dow.value - today.dayOfWeek.value + 7) % 7).toLong()).let { if (it == today) it.plusDays(7) else it }
                    if (m.groupValues[1].trim().equals("next", true) && d.isBefore(today.plusDays(7))) d = d.plusDays(7)
                    day = d
                } ?: take(Regex("\\s(?:on )?(\\d{1,2})(?:st|nd|rd|th)? (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s", I))?.let { m ->
                    day = monthDay(today, monthOf(m.groupValues[2]), m.groupValues[1].toInt())
                } ?: take(Regex("\\s(?:on )?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* (\\d{1,2})(?:st|nd|rd|th)?\\s", I))?.let { m ->
                    day = monthDay(today, monthOf(m.groupValues[1]), m.groupValues[2].toInt())
                } ?: take(Regex("\\sin (\\d+) (day|days|week|weeks)\\s", I))?.let { m ->
                    day = today.plusDays(m.groupValues[1].toLong() * if (m.groupValues[2].startsWith("week")) 7 else 1)
                }
            }
        }
        val title = text.replace(Regex("\\s+"), " ").trim().trim(',', '-').trim().replaceFirstChar { it.uppercase() }.ifBlank { "New event" }
        return Parsed(title, day, if (allDay) null else time, minutes, allDay || time == null && Regex("all day", RegexOption.IGNORE_CASE).containsMatchIn(input))
    }

    private fun at(h: Int, m: Int, ap: String): LocalTime? {
        var hour = h
        if (ap == "pm" && hour < 12) hour += 12 else if (ap == "am" && hour == 12) hour = 0
        else if (ap.isEmpty() && hour in 1..7) hour += 12 // "at 3" means the afternoon
        return if (hour in 0..23 && m in 0..59) LocalTime.of(hour, m) else null
    }
    private fun monthOf(s: String) = Month.entries.first { it.name.lowercase().startsWith(s.lowercase().take(3)) }
    private fun monthDay(today: LocalDate, m: Month, d: Int): LocalDate = runCatching { LocalDate.of(today.year, m, d).let { if (it.isBefore(today)) it.plusYears(1) else it } }.getOrDefault(today)
}
