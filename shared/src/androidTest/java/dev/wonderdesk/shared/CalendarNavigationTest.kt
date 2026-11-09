package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarNavigationTest {
    @Test fun monthNavigationKeepsSelectionInDisplayedMonth() {
        assertEquals(LocalDate.of(2024, 2, 29), shiftCalendarSelection(LocalDate.of(2024, 1, 31), 1))
        assertEquals(LocalDate.of(2023, 2, 28), shiftCalendarSelection(LocalDate.of(2023, 1, 31), 1))
        assertEquals(LocalDate.of(2025, 12, 10), shiftCalendarSelection(LocalDate.of(2026, 1, 10), -1))
    }
}
