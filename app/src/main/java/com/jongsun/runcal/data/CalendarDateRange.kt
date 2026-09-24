package com.jongsun.runcal.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** [yearMonth] 1일 00:00 ~ 다음 달 1일 00:00 (exclusive) 범위를 epoch millis로 반환한다. */
fun monthRangeMillis(yearMonth: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val start = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

/**
 * [weekStartDay] 기준으로 [referenceDate]가 속한 주의 시작일(포함)~다음 주 시작일(exclusive)을
 * LocalDate 쌍으로 반환한다. CalendarGridBuilder의 월 그리드 오프셋 계산과 동일한 공식을 쓴다.
 */
fun weekRange(referenceDate: LocalDate, weekStartDay: DayOfWeek): Pair<LocalDate, LocalDate> {
    val offset = ((referenceDate.dayOfWeek.value - weekStartDay.value) + 7) % 7
    val start = referenceDate.minusDays(offset.toLong())
    return start to start.plusDays(7)
}

/** [weekRange]의 LocalDate 범위를 epoch millis [start, endExclusive)로 변환한다. */
fun weekRangeMillis(referenceDate: LocalDate, weekStartDay: DayOfWeek, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val (start, endExclusive) = weekRange(referenceDate, weekStartDay)
    return start.atStartOfDay(zone).toInstant().toEpochMilli() to endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
}
