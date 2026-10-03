package com.jongsun.runcal

import com.jongsun.runcal.data.formatClock
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun twentyFourHour() {
        assertEquals("00:05", formatClock(0, 5, use24h = true))
        assertEquals("14:30", formatClock(14, 30, use24h = true))
    }

    @Test
    fun twelveHourMidnightAndNoon() {
        assertEquals("오전 12:05", formatClock(0, 5, use24h = false))
        assertEquals("오전 7:30", formatClock(7, 30, use24h = false))
        assertEquals("오후 12:00", formatClock(12, 0, use24h = false))
        assertEquals("오후 11:59", formatClock(23, 59, use24h = false))
    }
}
