package com.jongsun.runcal

import com.jongsun.runcal.data.share.IcsParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private val sample = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        SUMMARY:인터벌 400m\, 8회
        DTSTART;TZID=Asia/Seoul:20261005T070000
        DTEND;TZID=Asia/Seoul:20261005T083000
        LOCATION:한강공원
        DESCRIPTION:워밍업 2km\n쿨다운 1km
        END:VEVENT
        BEGIN:VEVENT
        SUMMARY:하프 마라톤
        DTSTART;VALUE=DATE:20261018
        DTEND;VALUE=DATE:20261019
        END:VEVENT
        BEGIN:VEVENT
        SUMMARY:회의
        DTSTART:20261006T010000Z
        DURATION:PT45M
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun parsesTimedAllDayAndUtc() {
        val events = IcsParser.parse(sample, seoul)
        assertEquals(3, events.size)

        val run = events[0]
        assertEquals("인터벌 400m, 8회", run.title)
        assertEquals(LocalDateTime.of(2026, 10, 5, 7, 0).atZone(seoul).toInstant().toEpochMilli(), run.startMillis)
        assertEquals(90 * 60_000L, run.endMillis - run.startMillis)
        assertEquals("한강공원", run.location)
        assertEquals("워밍업 2km\n쿨다운 1km", run.description)

        val race = events[1]
        assertTrue(race.allDay)
        assertEquals(LocalDate.of(2026, 10, 18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), race.startMillis)
        assertEquals(24 * 60 * 60_000L, race.endMillis - race.startMillis)

        val meeting = events[2]
        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 0).atZone(seoul).toInstant().toEpochMilli(), meeting.startMillis)
        assertEquals(45 * 60_000L, meeting.endMillis - meeting.startMillis)
    }

    /** claude-skill/runcal/SKILL.md가 안내하는 .ics 형식이 그대로 읽히는지. */
    @Test
    fun parsesSkillTemplate() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//RunCal skill//KO
            BEGIN:VEVENT
            UID:abc@runcal
            SUMMARY:병원
            DTSTART;TZID=Asia/Seoul:20261007T070000
            DTEND;TZID=Asia/Seoul:20261007T080000
            END:VEVENT
            BEGIN:VEVENT
            UID:def@runcal
            SUMMARY:하프 마라톤
            DTSTART;VALUE=DATE:20261018
            DTEND;VALUE=DATE:20261019
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = IcsParser.parse(ics, seoul)
        assertEquals(listOf("병원", "하프 마라톤"), events.map { it.title })
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0).atZone(seoul).toInstant().toEpochMilli(), events[0].startMillis)
        assertTrue(events[1].allDay)
    }

    @Test
    fun unfoldsContinuationLines() {
        val folded = "BEGIN:VEVENT\r\nSUMMARY:긴 제목\r\n  이어짐\r\nDTSTART:20261005T000000Z\r\nEND:VEVENT\r\n"
        assertEquals("긴 제목 이어짐", IcsParser.parse(folded, seoul).single().title)
    }
}
