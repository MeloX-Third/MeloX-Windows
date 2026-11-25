package melox.dailyhistory

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class DailyClockTest {
    @Test fun archiveMarkersAndEmptyStatesShareTheSameDateKeys() {
        val date = LocalDate.of(2026, 11, 25)
        val key = MeloXDailyClock.archiveKeyFor(date)
        assertEquals(setOf(date), MeloXDailyClock.markedDates(listOf(key, key, "invalid", "2026-02-30")))
        assertEquals(EmptyState.NO_HISTORY, MeloXDailyClock.isEmptyState(emptyList(), key))
        assertEquals(EmptyState.NO_SELECTION, MeloXDailyClock.isEmptyState(listOf(key), null))
        assertEquals(EmptyState.NO_RECORD_THAT_DAY, MeloXDailyClock.isEmptyState(listOf(key), "2026-11-24"))
        assertEquals(EmptyState.HAS_RECORD, MeloXDailyClock.isEmptyState(listOf(key), key))
    }
    @Test fun leapMonthUsesStableSixRowsAndHonorsWeekStart() {
        val month = YearMonth.of(2024, 2)
        val monday = MeloXDailyClock.monthCells(month)
        assertEquals(42, monday.size)
        assertEquals(29, monday.filterNotNull().size)
        assertEquals(month.atDay(1), monday[3])
        assertEquals(month.atEndOfMonth(), monday[31])
        assertEquals(month.atDay(1), MeloXDailyClock.monthCells(month, DayOfWeek.SUNDAY)[4])
    }

    @Test fun pagingNeverKeepsAnotherMonthsRecord() {
        val today = LocalDate.of(2026, 11, 25)
        val october = YearMonth.of(2026, 10)
        val dates = listOf("2026-11-25", "2026-10-30", "2026-10-12")
        assertEquals("2026-10-30", MeloXDailyClock.selectionForMonth(october, "2026-11-25", today, dates))
        assertEquals("2026-10-12", MeloXDailyClock.selectionForMonth(october, "2026-10-12", today, dates))
        assertNull(MeloXDailyClock.selectionForMonth(YearMonth.of(2026, 9), null, today, dates))
        assertEquals("2026-11-25", MeloXDailyClock.selectionForMonth(YearMonth.of(2026, 11), null, today, dates))
        assertNull(MeloXDailyClock.parseKey("2026-02-30"))
    }
}
