package com.jongsun.runcal.data.notion

import com.jongsun.runcal.data.CalendarRepository.Companion.DEFAULT_CALENDAR_COLOR
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.room.NotionDatabaseDao
import com.jongsun.runcal.data.room.NotionEventDao
import com.jongsun.runcal.data.source.EventSource
import com.jongsun.runcal.data.source.EventSourceKind
import com.jongsun.runcal.data.source.SourceRef

/**
 * Room 캐시만 읽는다 — 이 클래스 안에 네트워크 호출이 전혀 없다.
 * 실제 Notion API 조회/갱신은 [com.jongsun.runcal.work.NotionSyncWorker]가 별도로 수행하고
 * 이 클래스는 그 결과(캐시)만 읽으므로, 위젯 렌더링 경로의 200ms 예산에 영향을 주지 않는다.
 */
class NotionEventSource(
    private val notionEventDao: NotionEventDao,
    private val notionDatabaseDao: NotionDatabaseDao,
) : EventSource {

    override val kind: EventSourceKind = EventSourceKind.NOTION

    override suspend fun getEvents(startMillis: Long, endMillis: Long, refs: Set<SourceRef>?): List<EventItem> {
        val registrationIds = refs?.filterIsInstance<SourceRef.NotionDatabase>()?.map { it.registrationId }
        val rows = when {
            registrationIds == null -> notionEventDao.getEventsInRangeAllDbs(startMillis, endMillis)
            registrationIds.isEmpty() -> return emptyList()
            else -> notionEventDao.getEventsInRange(registrationIds, startMillis, endMillis)
        }
        if (rows.isEmpty()) return emptyList()

        val colorByRegistration = notionDatabaseDao.getAll().associate { it.id to it.colorArgb }
        return rows.map { row ->
            EventItem(
                // Notion 페이지 id는 문자열(UUID)이라 EventItem.id(Long)에 직접 담을 수 없다.
                // 여기 담기는 값은 UI 리스트 diffing용일 뿐, 실제 식별자는 notionPageId다
                // (Notion은 읽기 전용이라 이 id로 쓰기를 타겟팅하는 일이 없다).
                id = row.notionPageId.hashCode().toLong(),
                calendarId = 0L,
                title = row.title,
                begin = row.startMillis,
                end = row.endMillis,
                allDay = row.allDay,
                color = colorByRegistration[row.registrationId] ?: DEFAULT_CALENDAR_COLOR,
                location = "",
                sourceKind = EventSourceKind.NOTION,
                notionPageId = row.notionPageId,
                notionDatabaseId = row.registrationId,
                notionStatus = row.statusRaw,
            )
        }
    }
}
