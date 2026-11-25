package melox.dailyhistory

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Local natural day and stable calendar geometry, matching Android edc829e. */
object MeloXDailyClock {
    fun today(): LocalDate = LocalDate.now()
    fun todayKey(): String = today().toString()
    fun archiveKeyFor(date: LocalDate): String = date.toString()
    fun parseKey(key: String): LocalDate? =
        if (DailyHistoryStore.validDate(key)) LocalDate.parse(key) else null

    fun markedDates(keys: List<String>): Set<LocalDate> = keys.mapNotNull(::parseKey).toSet()

    fun isEmptyState(dateKeys: List<String>, selectedKey: String?): EmptyState = when {
        dateKeys.isEmpty() -> EmptyState.NO_HISTORY
        selectedKey == null -> EmptyState.NO_SELECTION
        selectedKey !in dateKeys -> EmptyState.NO_RECORD_THAT_DAY
        else -> EmptyState.HAS_RECORD
    }

    fun monthCells(month: YearMonth, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate?> {
        val leading = (month.atDay(1).dayOfWeek.value - weekStart.value + 7) % 7
        return (0 until 42).map { index ->
            (index - leading + 1).takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
        }
    }

    fun selectionForMonth(month: YearMonth, selected: String?, today: LocalDate = today(), dates: List<String> = emptyList()): String? {
        val previous = selected?.let(::parseKey)
        return when {
            previous != null && YearMonth.from(previous) == month -> selected
            YearMonth.from(today) == month -> today.toString()
            else -> dates.mapNotNull(::parseKey).firstOrNull { YearMonth.from(it) == month }?.toString()
        }
    }
}

enum class EmptyState { NO_HISTORY, NO_RECORD_THAT_DAY, NO_SELECTION, HAS_RECORD }
