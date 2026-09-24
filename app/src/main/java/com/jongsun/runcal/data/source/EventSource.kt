package com.jongsun.runcal.data.source

import com.jongsun.runcal.data.EventItem

/** 이벤트가 어느 소스에서 왔는지. [com.jongsun.runcal.data.EventItem.sourceKind]에 쓰인다. */
enum class EventSourceKind { CALENDAR, NOTION }

/** 캘린더 ID 또는 (향후) Notion DB 등록 ID를 가리키는 참조. */
sealed interface SourceRef {
    data class Calendar(val calendarId: Long) : SourceRef
    data class NotionDatabase(val registrationId: String) : SourceRef
}

/**
 * 프리셋 등에서 "어떤 소스를 볼지"를 나타낸다.
 * 각 필드는 null=해당 종류 전체, 빈 집합=해당 종류 없음, 비어있지 않은 집합=그 안의 것만 —
 * 기존 [com.jongsun.runcal.widget.WidgetPreset.calendarIds]의 null/empty 관례를 그대로 확장한다.
 */
data class SourceSelection(
    val calendarIds: Set<Long>?,
    val notionDbIds: Set<String>?,
)

/** 캘린더/Notion을 동일하게 다루기 위한 읽기 전용 인터페이스. 쓰기는 이 인터페이스에 없다(Notion은 읽기 전용). */
interface EventSource {
    val kind: EventSourceKind
    suspend fun getEvents(startMillis: Long, endMillis: Long, refs: Set<SourceRef>?): List<EventItem>
}
