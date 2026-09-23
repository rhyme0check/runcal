package com.jongsun.runcal.ui.calendar

import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.isBarWorthy
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val MONTH_GRID_WEEKS = 6
const val DAYS_IN_WEEK = 7

/** 한 주 최대 표시 막대(종일/다일간 일정) 레인 수. 넘치는 일정은 화면에서 생략된다. */
const val MAX_BAR_LANES = 3

data class MonthGridDay(
    val date: LocalDate,
    val isCurrentMonth: Boolean,
)

data class MonthGridWeek(
    val days: List<MonthGridDay>,
)

/** [event]가 특정 주(week row)에서 차지하는 칸 구간 하나. */
data class EventBar(
    val event: EventItem,
    val startCol: Int,
    val endCol: Int,
    val isTrueStart: Boolean,
    val isTrueEnd: Boolean,
)

/** [weekStartDay]를 기준으로 [yearMonth]의 6주 x 7일 그리드를 만든다. */
fun buildMonthGridWeeks(yearMonth: YearMonth, weekStartDay: DayOfWeek): List<MonthGridWeek> {
    val firstOfMonth = yearMonth.atDay(1)
    val offset = ((firstOfMonth.dayOfWeek.value - weekStartDay.value) + DAYS_IN_WEEK) % DAYS_IN_WEEK
    val gridStart = firstOfMonth.minusDays(offset.toLong())

    return (0 until MONTH_GRID_WEEKS).map { week ->
        MonthGridWeek(
            (0 until DAYS_IN_WEEK).map { col ->
                val date = gridStart.plusDays((week * DAYS_IN_WEEK + col).toLong())
                MonthGridDay(
                    date = date,
                    isCurrentMonth = date.year == yearMonth.year && date.month == yearMonth.month,
                )
            },
        )
    }
}

/** 그리드 전체(6주 x 7일)가 실제로 덮는 [start, end) 날짜 범위. */
fun monthGridDateRange(weeks: List<MonthGridWeek>): Pair<LocalDate, LocalDate> {
    val start = weeks.first().days.first().date
    val endExclusive = weeks.last().days.last().date.plusDays(1)
    return start to endExclusive
}

/**
 * 이번 주에 걸친 종일/다일간 일정을 레인별로 배치한다.
 * 반환값: 레인 목록(각 레인은 7칸짜리 목록이며 일정이 없는 칸은 null).
 */
fun computeWeekBars(
    week: MonthGridWeek,
    events: List<EventItem>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<List<EventBar?>> {
    val weekStart = week.days.first().date
    val weekEnd = week.days.last().date

    val candidates = events
        .filter { it.isBarWorthy(zone) }
        .mapNotNull { event ->
            val range = event.dateRange(zone)
            if (range.endInclusive < weekStart || range.start > weekEnd) return@mapNotNull null
            val clampedStart = maxOf(range.start, weekStart)
            val clampedEnd = minOf(range.endInclusive, weekEnd)
            EventBar(
                event = event,
                startCol = ChronoUnit.DAYS.between(weekStart, clampedStart).toInt(),
                endCol = ChronoUnit.DAYS.between(weekStart, clampedEnd).toInt(),
                isTrueStart = range.start == clampedStart,
                isTrueEnd = range.endInclusive == clampedEnd,
            )
        }
        .sortedWith(compareBy({ it.startCol }, { it.startCol - it.endCol }))

    val laneEnds = mutableListOf<Int>()
    val lanes = mutableListOf<MutableList<EventBar?>>()

    for (bar in candidates) {
        var lane = laneEnds.indexOfFirst { it < bar.startCol }
        if (lane == -1) {
            if (lanes.size >= MAX_BAR_LANES) continue // 표시 한도 초과 - 생략
            laneEnds += bar.endCol
            lanes += MutableList(DAYS_IN_WEEK) { null }
            lane = lanes.lastIndex
        } else {
            laneEnds[lane] = bar.endCol
        }
        for (col in bar.startCol..bar.endCol) {
            lanes[lane][col] = bar
        }
    }
    return lanes
}
