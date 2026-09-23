package com.jongsun.runcal.widget

import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.ui.calendar.DAYS_IN_WEEK
import com.jongsun.runcal.ui.calendar.EventBar
import com.jongsun.runcal.ui.calendar.MonthGridWeek
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * [lanes]: 위에서부터 쌓이는 막대 레인들(각 레인은 7칸, 없는 칸은 null).
 * [overflowCountByCol]: 레인 한도를 넘어 생략된 일정 수(요일 칸별). 0이면 "+N" 표시 없음.
 */
data class WidgetWeekBars(
    val lanes: List<List<EventBar?>>,
    val overflowCountByCol: IntArray,
)

/**
 * 앱 화면의 computeWeekBars()(ui/calendar/CalendarGridBuilder.kt)와 같은 레인 패킹 알고리즘을 쓰되
 * 두 가지가 다르다:
 * 1) isBarWorthy() 필터 없이 "모든" 일정을 막대로 취급한다 — 위젯은 하단 아젠다 패널이 없어
 *    Samsung 캘린더 위젯처럼 단일 당일 일정도 막대로 보여줘야 한다.
 * 2) 레인 한도([maxLanes], 글자크기 단계에서 옴)를 넘는 일정을 조용히 버리지 않고
 *    날짜별 "+N" 카운트로 돌려준다.
 */
fun computeWidgetWeekBars(
    week: MonthGridWeek,
    events: List<EventItem>,
    maxLanes: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): WidgetWeekBars {
    val weekStart = week.days.first().date
    val weekEnd = week.days.last().date

    val candidates = events
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
        // 여러 날에 걸친(더 긴) 일정을 먼저 배치해야 레인이 안정적으로 이어진다.
        .sortedWith(compareBy({ it.startCol }, { it.startCol - it.endCol }))

    val laneEnds = mutableListOf<Int>()
    val lanes = mutableListOf<MutableList<EventBar?>>()
    val overflowCountByCol = IntArray(DAYS_IN_WEEK)

    for (bar in candidates) {
        var lane = laneEnds.indexOfFirst { it < bar.startCol }
        if (lane == -1) {
            if (lanes.size >= maxLanes) {
                for (col in bar.startCol..bar.endCol) overflowCountByCol[col]++
                continue
            }
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
    return WidgetWeekBars(lanes, overflowCountByCol)
}
