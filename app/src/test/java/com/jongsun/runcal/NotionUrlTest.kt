package com.jongsun.runcal

import com.jongsun.runcal.data.notion.parseNotionDatabaseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotionUrlTest {
    private val id = "3c2ddfc8-d4b3-81fe-b42f-dab879402914"
    private val hex = id.replace("-", "")

    @Test
    fun fullPageDatabaseLinkWithView() {
        assertEquals(id, parseNotionDatabaseId("https://www.notion.so/myspace/$hex?v=1111aaaa2222bbbb3333cccc4444dddd"))
        assertEquals(id, parseNotionDatabaseId("https://www.notion.so/myspace/훈련일지-$hex?v=1111aaaa2222bbbb3333cccc4444dddd&pvs=4"))
    }

    @Test
    fun nameEndingWithHexLetters() {
        assertEquals(id, parseNotionDatabaseId("https://www.notion.so/Running-Cafe-$hex?v=abc"))
    }

    @Test
    fun bareIdAndDashedId() {
        assertEquals(id, parseNotionDatabaseId(hex))
        assertEquals(id, parseNotionDatabaseId(id))
    }

    @Test
    fun noIdReturnsNull() {
        assertNull(parseNotionDatabaseId("https://www.notion.so/myspace/just-a-page"))
    }
}
