package com.jongsun.runcal.data.source

import com.jongsun.runcal.data.CalendarRepository
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.notion.NotionEventSource

/** 캘린더 + Notion 소스를 [SourceSelection]에 따라 나눠 조회한 뒤 시간순으로 합친다. */
class EventRepository(
    private val calendarSource: CalendarRepository,
    private val notionSource: NotionEventSource,
) {
    suspend fun getEvents(startMillis: Long, endMillis: Long, selection: SourceSelection): List<EventItem> {
        val calendarEvents = if (selection.calendarIds?.isEmpty() == true) {
            emptyList()
        } else {
            calendarSource.getEvents(startMillis, endMillis, selection.calendarIds?.map { SourceRef.Calendar(it) }?.toSet())
        }
        val notionEvents = if (selection.notionDbIds?.isEmpty() == true) {
            emptyList()
        } else {
            notionSource.getEvents(startMillis, endMillis, selection.notionDbIds?.map { SourceRef.NotionDatabase(it) }?.toSet())
        }
        return (calendarEvents + notionEvents).sortedBy { it.begin }
    }
}
