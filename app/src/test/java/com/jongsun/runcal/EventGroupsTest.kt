package com.jongsun.runcal

import com.jongsun.runcal.data.EventGroupIndex
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.room.EventTypeEntity
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.withGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventGroupsTest {
    private val point = EventTypeEntity("p", "포인트훈련", 0xFFFF0000.toInt(), null, null, 1, "RP, RACE")
    private val easy = EventTypeEntity("e", "이지훈련", 0xFF00FF00.toInt(), null, null, 2, "EASY, REC, LSD")
    private val work = EventTypeEntity("w", "업무", 0xFF0000FF.toInt(), null, null, 3, "")

    private fun notion(title: String, page: String = title) = EventItem(
        id = page.hashCode().toLong(), calendarId = 0, title = title, begin = 0, end = 0, allDay = true, color = 0,
        sourceKind = EventSourceKind.NOTION, notionDatabaseId = "db", notionPageId = page, eventColor = null,
    )

    private fun calendar(id: Long, title: String, eventColor: Int? = null) =
        EventItem(id = id, calendarId = 3, title = title, begin = 0, end = 0, allDay = false, color = 0, eventColor = eventColor)

    @Test
    fun titleRulesMatchPrefixIgnoringEmojiAndCase() {
        val index = EventGroupIndex(listOf(point, easy, work), emptyMap(), emptyMap())
        assertEquals("p", index.groupOf(notion("⚡ RP 4km*2(422)+2min"))?.id)
        assertEquals("p", index.groupOf(notion("RP2 4km(420)"))?.id)
        assertEquals("e", index.groupOf(notion("easy 8km(cap145) + 힐스트라이드"))?.id)
        assertEquals("e", index.groupOf(notion("🏞 LSD 20km"))?.id)
        assertNull(index.groupOf(notion("REST")))
    }

    @Test
    fun manualAssignmentWinsOverRulesAndEmptyMeansNone() {
        val notionAssign = mapOf(
            EventGroupIndex.notionKey("db", "a") to "e",
            EventGroupIndex.notionKey("db", "b") to "",
        )
        val index = EventGroupIndex(listOf(point, easy, work), mapOf(10L to "w"), notionAssign)
        assertEquals("e", index.groupOf(notion("RP 6km", page = "a"))?.id)
        assertNull(index.groupOf(notion("RP 6km", page = "b")))
        assertEquals("w", index.groupOf(calendar(10, "RP 회의"))?.id)
        assertEquals("p", index.groupOf(calendar(11, "RP 회의"))?.id)
    }

    @Test
    fun colorPriorityPresetThenGroupThenEvent() {
        val index = EventGroupIndex(listOf(point), emptyMap(), emptyMap())
        val grouped = calendar(1, "RP 6km", eventColor = 0xFF123456.toInt())
        assertEquals(point.colorArgb, grouped.withGroup(index, overrideColor = null).displayColor)
        assertEquals(0xFFABCDEF.toInt(), grouped.withGroup(index, overrideColor = 0xFFABCDEF.toInt()).displayColor)
        // 그룹이 없으면 displayColor가 비어 일정 색(eventColor)이 쓰인다.
        assertNull(calendar(2, "회의", eventColor = 0xFF123456.toInt()).withGroup(index, overrideColor = null).displayColor)
    }
}
