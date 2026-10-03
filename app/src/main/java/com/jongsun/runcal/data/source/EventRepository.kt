package com.jongsun.runcal.data.source

import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.EventGroupIndex
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.notion.NotionEventSource
import com.jongsun.runcal.data.withGroup

/**
 * 캘린더 + Notion 소스를 [SourceSelection]에 따라 나눠 조회한 뒤 시간순으로 합친다.
 * 각 일정에 일정그룹과 그릴 색을 붙이고([withGroup]), 선택에 그룹이 있으면 "고른 캘린더·DB의 일정 ∪ 고른 그룹의 일정"을 돌려준다.
 */
class EventRepository(
    private val calendarSource: CalendarRepository,
    private val notionSource: NotionEventSource,
    private val groupIndex: suspend () -> EventGroupIndex = { EventGroupIndex.EMPTY },
) {
    suspend fun getEvents(startMillis: Long, endMillis: Long, selection: SourceSelection): List<EventItem> {
        val index = groupIndex()
        val groupIds = selection.groupIds?.takeIf { it.isNotEmpty() }
        if (groupIds == null) {
            return fetch(startMillis, endMillis, selection.calendarIds, selection.notionDbIds)
                .map { it.withGroup(index, selection.overrideColor) }
        }
        // 그룹은 어느 캘린더·DB에 있든 모아야 하므로 전부 읽은 뒤 거른다(로컬 조회라 비용이 작다).
        return fetch(startMillis, endMillis, null, null)
            .map { it.withGroup(index, selection.overrideColor) }
            .filter { e -> e.groupId in groupIds || inSources(e, selection) }
    }

    private fun inSources(event: EventItem, selection: SourceSelection): Boolean = when (event.sourceKind) {
        EventSourceKind.CALENDAR -> selection.calendarIds?.contains(event.calendarId) ?: true
        EventSourceKind.NOTION -> selection.notionDbIds?.contains(event.notionDatabaseId) ?: true
    }

    private suspend fun fetch(startMillis: Long, endMillis: Long, calendarIds: Set<Long>?, notionDbIds: Set<String>?): List<EventItem> {
        val calendarEvents = if (calendarIds?.isEmpty() == true) {
            emptyList()
        } else {
            calendarSource.getEvents(startMillis, endMillis, calendarIds?.map { SourceRef.Calendar(it) }?.toSet())
        }
        val notionEvents = if (notionDbIds?.isEmpty() == true) {
            emptyList()
        } else {
            notionSource.getEvents(startMillis, endMillis, notionDbIds?.map { SourceRef.NotionDatabase(it) }?.toSet())
        }
        return (calendarEvents + notionEvents).sortedBy { it.begin }
    }
}
