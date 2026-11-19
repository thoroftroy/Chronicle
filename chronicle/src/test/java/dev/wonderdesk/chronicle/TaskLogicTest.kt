package dev.wonderdesk.chronicle

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TaskLogicTest {
    private val mon = LocalDate.of(2026, 11, 16)   // a Monday
    private fun daily(title: String = "Closing duties", items: List<String> = listOf("Lock the till", "Turn off lights", "Take out trash")) =
        Task(title = title, due = mon.toEpochDay() * 86_400_000L, repeat = "daily", anchor = mon.toEpochDay(), checklist = items.map { CheckItem(text = it) }, created = 0L)

    @Test fun daily_comes_up_every_day_from_its_start() {
        val t = daily()
        assertFalse(TaskLogic.scheduledOn(t, mon.minusDays(1)))
        assertTrue(TaskLogic.scheduledOn(t, mon))
        assertTrue(TaskLogic.scheduledOn(t, mon.plusDays(30)))
    }

    @Test fun every_n_days_weekly_weekdays_monthly() {
        val every3 = daily().copy(every = 3)
        assertTrue(TaskLogic.scheduledOn(every3, mon.plusDays(3))); assertFalse(TaskLogic.scheduledOn(every3, mon.plusDays(4)))
        val weekdays = daily().copy(repeat = "weekdays")
        assertTrue(TaskLogic.scheduledOn(weekdays, mon.plusDays(4))); assertFalse(TaskLogic.scheduledOn(weekdays, mon.plusDays(5)))
        val weekly = daily().copy(repeat = "weekly", days = (1 shl 0) or (1 shl 3))   // Mon and Thu
        assertTrue(TaskLogic.scheduledOn(weekly, mon.plusDays(3))); assertFalse(TaskLogic.scheduledOn(weekly, mon.plusDays(2))); assertTrue(TaskLogic.scheduledOn(weekly, mon.plusDays(7)))
        val monthly = daily().copy(repeat = "monthly")
        assertTrue(TaskLogic.scheduledOn(monthly, LocalDate.of(2026, 12, 16))); assertFalse(TaskLogic.scheduledOn(monthly, LocalDate.of(2026, 12, 17)))
    }

    @Test fun ticking_every_item_finishes_the_day_and_tomorrow_starts_fresh() {
        var t = daily()
        t.checklist.forEach { t = TaskLogic.toggleCheck(t, mon, it.id) }
        assertTrue(TaskLogic.doneOn(t, mon))
        assertFalse(TaskLogic.doneOn(t, mon.plusDays(1)))
        assertTrue(TaskLogic.checkedOn(t, mon.plusDays(1)).isEmpty())
        // Unticking one reopens the day.
        t = TaskLogic.toggleCheck(t, mon, t.checklist[0].id)
        assertFalse(TaskLogic.doneOn(t, mon))
        assertEquals(2, TaskLogic.checkedOn(t, mon).size)
    }

    @Test fun a_day_is_rewarded_only_when_everything_that_came_up_is_done() {
        val a = daily("A", listOf()); val b = daily("B", listOf())
        var list = listOf(a, b)
        assertEquals(0 to 2, TaskLogic.progress(list, mon))
        list = listOf(TaskLogic.setDone(a, mon, true), b)
        assertEquals(1 to 2, TaskLogic.progress(list, mon))
        list = list.map { TaskLogic.setDone(it, mon, true) }
        assertEquals(2 to 2, TaskLogic.progress(list, mon))
        // The next day is its own count.
        assertEquals(0 to 2, TaskLogic.progress(list, mon.plusDays(1)))
    }

    @Test fun one_off_tasks_count_on_their_due_day() {
        val one = Task(title = "Pay rent", due = mon.plusDays(2).toEpochDay() * 86_400_000L, created = 0L)
        assertEquals(0 to 1, TaskLogic.progress(listOf(one), mon.plusDays(2)))
        assertEquals(0 to 0, TaskLogic.progress(listOf(one), mon))
        val done = TaskLogic.setDone(one, mon.plusDays(2), true)
        assertEquals(1 to 1, TaskLogic.progress(listOf(done), mon.plusDays(2)))
    }

    @Test fun json_round_trip_keeps_everything() {
        var t = daily().copy(list = "Work", asEvent = true, minutes = 30, dueHasTime = true)
        t = TaskLogic.toggleCheck(t, mon, t.checklist[0].id)
        t = TaskLogic.setDone(t, mon.minusDays(1), true)
        val back = Task.from(t.toJson())
        assertEquals(t.checklist, back.checklist)
        assertEquals(t.history, back.history)
        assertEquals(t.checks, back.checks)
        assertEquals("Work", back.list); assertEquals("daily", back.repeat); assertTrue(back.asEvent); assertEquals(30, back.minutes)
    }

    @Test fun old_tasks_without_the_new_fields_still_load() {
        val o = org.json.JSONObject().put("id", "x").put("title", "Old").put("due", mon.toEpochDay() * 86_400_000L)
        val t = Task.from(o)
        assertEquals("Old", t.title); assertEquals("", t.repeat); assertTrue(t.checklist.isEmpty())
        assertEquals(mon, TaskLogic.dueDay(t))
    }

    @Test fun next_day_skips_what_is_already_done_today() {
        val today = LocalDate.now()
        var t = daily().copy(anchor = today.minusDays(3).toEpochDay())
        assertEquals(today, TaskLogic.nextDay(t, today))
        t = TaskLogic.setDone(t, today, true)
        assertEquals(today.plusDays(1), TaskLogic.nextDay(t, today))
    }
}
