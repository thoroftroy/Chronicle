package dev.wonderdesk.chronicle

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskRemindersTest {
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 11, 26)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun task(h: Int = 10, remind: List<Int> = listOf(0), nag: Int = 0) =
        Task(title = "Bins", due = at(day, h), dueHasTime = true, remind = remind, nag = nag, created = 0L)

    @Test fun reminders_before_and_at_the_time() {
        val t = task(remind = listOf(0, 60))
        TaskLogic.nextReminder(t, at(day, 8))!!.let { assertEquals(at(day, 9), it.at); assertEquals("before", it.kind); assertEquals(60, it.minutes) }
        TaskLogic.nextReminder(t, at(day, 9, 30))!!.let { assertEquals(at(day, 10), it.at); assertEquals("due", it.kind) }
        assertNull(TaskLogic.nextReminder(t, at(day, 10, 30)))
    }

    @Test fun nags_until_done_and_stay_quiet_overnight() {
        val t = task(nag = 30)
        TaskLogic.nextReminder(t, at(day, 10, 5))!!.let { assertEquals(at(day, 10, 30), it.at); assertEquals("nag", it.kind); assertEquals(30, it.minutes) }
        val late = task(h = 22, nag = 60)
        assertEquals(at(day.plusDays(1), 7), TaskLogic.nextReminder(late, at(day, 22, 1))!!.at)
        assertNull(TaskLogic.nextReminder(TaskLogic.setDone(t, day, true), at(day, 10, 5)))
    }

    @Test fun no_reminders_means_quiet() {
        assertNull(TaskLogic.nextReminder(task(remind = emptyList()), at(day, 8)))
    }

    @Test fun repeating_task_finished_today_waits_for_tomorrow() {
        val t = task(remind = listOf(0, 1440)).copy(repeat = "daily", anchor = day.toEpochDay())
        val done = TaskLogic.setDone(t, day, true)
        // "A day before" tomorrow's round rings today at 10:00; tomorrow's own round at 10:00 tomorrow.
        TaskLogic.nextReminder(done, at(day, 8))!!.let { assertEquals(at(day, 10), it.at); assertEquals(day.plusDays(1), it.day) }
        TaskLogic.nextReminder(done, at(day, 10, 1))!!.let { assertEquals(at(day.plusDays(1), 10), it.at) }
    }

    @Test fun labels_read_naturally() {
        assertEquals("At the time, 1 day before · then every 30 minutes until done", TaskLogic.reminderLabel(task(remind = listOf(1440, 0), nag = 30)))
        assertEquals("2 hours", TaskLogic.span(120)); assertEquals("1 week", TaskLogic.span(10080))
    }
}
