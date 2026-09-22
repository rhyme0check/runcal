package com.jongsun.runcal.widget

import java.time.LocalDate
import java.time.YearMonth

data class CalendarDay(
    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val schedules: List<ScheduleEntry>,
)

private const val WEEKS_IN_GRID = 6
private const val DAYS_IN_WEEK = 7

/** 일요일 시작 6주 x 7일 달력 그리드를 생성한다. [eventsByDay]는 해당 월의 일(day-of-month) 기준 일정 맵이다. */
fun buildMonthGrid(
    yearMonth: YearMonth,
    today: LocalDate,
    eventsByDay: Map<Int, List<ScheduleEntry>>,
): List<List<CalendarDay>> {
    val firstOfMonth = yearMonth.atDay(1)
    // DayOfWeek.value: MONDAY=1 .. SUNDAY=7 -> 일요일을 0으로 하는 인덱스로 변환
    val firstDayOfWeekIndex = firstOfMonth.dayOfWeek.value % 7
    val gridStart = firstOfMonth.minusDays(firstDayOfWeekIndex.toLong())

    return (0 until WEEKS_IN_GRID).map { week ->
        (0 until DAYS_IN_WEEK).map { dayIndex ->
            val date = gridStart.plusDays((week * DAYS_IN_WEEK + dayIndex).toLong())
            val isCurrentMonth = date.year == yearMonth.year && date.month == yearMonth.month
            CalendarDay(
                date = date,
                isCurrentMonth = isCurrentMonth,
                isToday = date == today,
                schedules = if (isCurrentMonth) {
                    eventsByDay[date.dayOfMonth].orEmpty()
                } else {
                    emptyList()
                },
            )
        }
    }
}
