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
    @Test fun eventEndAddsOnlyOfferedDurations() {
        assertEquals(1_800_000L, chronicleEventEnd(0L, 30))
        assertEquals(3_600_000L, chronicleEventEnd(0L, 60))
        assertEquals(7_200_000L, chronicleEventEnd(0L, 120))
        try { chronicleEventEnd(0L, 45); throw AssertionError("45 minutes accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
