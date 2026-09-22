package com.jongsun.runcal.data

import java.time.YearMonth
import java.time.ZoneId

/** [yearMonth] 1일 00:00 ~ 다음 달 1일 00:00 (exclusive) 범위를 epoch millis로 반환한다. */
fun monthRangeMillis(yearMonth: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val start = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}
