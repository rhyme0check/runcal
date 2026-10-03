package com.jongsun.runcal

import com.jongsun.runcal.data.notion.parseNotionSeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotionSeedTest {
    @Test
    fun parsesEntriesAndBlanks() {
        val seeds = parseNotionSeeds(
            "Run|3c2ddfc8d4b381feb42fdab879402914|FFF4511E|날짜|세션|일지|상태;Club|https://www.notion.so/x-37bddfc8d4b380528288f1d37356541f?v=1|FF8E24AA|Date|모임명||",
        )
        assertEquals(2, seeds.size)
        assertEquals("3c2ddfc8-d4b3-81fe-b42f-dab879402914", seeds[0].notionDatabaseId)
        assertEquals(0xFFF4511E.toInt(), seeds[0].colorArgb)
        assertEquals("상태", seeds[0].statusProperty)
        assertNull(seeds[1].subtitleProperty)
        assertNull(seeds[1].statusProperty)
    }

    @Test
    fun emptyOrBrokenGivesNothing() {
        assertEquals(0, parseNotionSeeds("").size)
        assertEquals(0, parseNotionSeeds("Run|nothex|FF000000|날짜|세션").size)
    }
}
