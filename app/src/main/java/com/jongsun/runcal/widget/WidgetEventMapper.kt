package com.jongsun.runcal.widget

import androidx.compose.ui.graphics.Color
import com.jongsun.runcal.data.EventItem
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** [events]를 [yearMonth]에 속하는 날짜별로 묶어 위젯 표시용 [ScheduleEntry] 목록으로 변환한다. */
fun groupEventsByDay(
    events: List<EventItem>,
    yearMonth: YearMonth,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<Int, List<ScheduleEntry>> {
    val grouped = LinkedHashMap<Int, MutableList<ScheduleEntry>>()
    events.forEach { event ->
        val eventZone = if (event.allDay) ZoneOffset.UTC else zone
        val date = Instant.ofEpochMilli(event.begin).atZone(eventZone).toLocalDate()
        if (date.year == yearMonth.year && date.month == yearMonth.month) {
            grouped.getOrPut(date.dayOfMonth) { mutableListOf() } += ScheduleEntry(
                text = event.title,
                dotColor = Color(event.color),
            )
        }
    }
    return grouped
}
