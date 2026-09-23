package com.jongsun.runcal.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 이벤트가 실제로 걸쳐 있는 로컬 날짜 범위(포함-포함). 종일 일정은 UTC 자정 기준으로 저장되고
 * DTEND가 마지막 날 다음날(배타적)이라 하루를 빼 마지막 날로 보정한다.
 */
fun EventItem.dateRange(zone: ZoneId = ZoneId.systemDefault()): ClosedRange<LocalDate> {
    val effectiveZone = if (allDay) ZoneOffset.UTC else zone
    val startDate = Instant.ofEpochMilli(begin).atZone(effectiveZone).toLocalDate()
    val lastMillis = if (allDay) end else (end - 1).coerceAtLeast(begin)
    val lastZone = if (allDay) ZoneOffset.UTC else zone
    val rawEndDate = Instant.ofEpochMilli(lastMillis).atZone(lastZone).toLocalDate()
    val endDate = if (allDay) rawEndDate.minusDays(1) else rawEndDate
    return startDate..maxOf(startDate, endDate)
}

/** 종일 일정이거나 이틀 이상에 걸친 일정인지 — 월간 화면에서 막대로 표시할 대상. */
fun EventItem.isBarWorthy(zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (allDay) return true
    val range = dateRange(zone)
    return range.start != range.endInclusive
}

/** [date]가 이 이벤트의 날짜 범위에 포함되는지. */
fun EventItem.occursOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val range = dateRange(zone)
    return date in range
}
