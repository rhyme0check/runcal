package com.jongsun.runcal

import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.dateRange
import com.jongsun.runcal.data.notion.NotionDateSpec
import com.jongsun.runcal.data.notion.NotionPropertyMapper
import com.jongsun.runcal.data.notion.NotionWriteService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Notion 날짜 ↔ 앱 내부 값 변환. 한국 시간에서 종일 항목이 하루 밀리지 않는지 확인한다. */
class NotionDateTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun item(begin: Long, end: Long, allDay: Boolean) =
        EventItem(id = 1, calendarId = 0, title = "t", begin = begin, end = end, allDay = allDay, color = 0)

    @Test
    fun allDaySingleDayStaysOnSameDateInSeoul() {
        val (begin, end, allDay) = NotionPropertyMapper.toEpochMillisRange("2026-10-05", null, seoul)
        val range = item(begin, end, allDay).dateRange(seoul)
        assertEquals(LocalDate.of(2026, 10, 5), range.start)
        assertEquals(LocalDate.of(2026, 10, 5), range.endInclusive)
    }

    @Test
    fun allDayRangeKeepsLastDayInclusive() {
        val (begin, end, allDay) = NotionPropertyMapper.toEpochMillisRange("2026-10-05", "2026-10-07", seoul)
        val range = item(begin, end, allDay).dateRange(seoul)
        assertEquals(LocalDate.of(2026, 10, 5), range.start)
        assertEquals(LocalDate.of(2026, 10, 7), range.endInclusive)
    }

    @Test
    fun specOfRoundTripsAllDay() {
        val (begin, end, _) = NotionPropertyMapper.toEpochMillisRange("2026-10-05", "2026-10-07", seoul)
        assertEquals(
            NotionDateSpec.AllDay(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7)),
            NotionWriteService.specOf(true, begin, end, seoul),
        )
    }

    @Test
    fun specOfRoundTripsTimed() {
        val (begin, end, allDay) = NotionPropertyMapper.toEpochMillisRange("2026-10-05T07:00:00.000+09:00", "2026-10-05T08:30:00.000+09:00", seoul)
        assertEquals(false, allDay)
        assertEquals(
            NotionDateSpec.Timed(LocalDateTime.of(2026, 10, 5, 7, 0), LocalDateTime.of(2026, 10, 5, 8, 30)),
            NotionWriteService.specOf(false, begin, end, seoul),
        )
    }
}
