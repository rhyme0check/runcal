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
 * [EventRepository]/[EventSource]에 실제로 넘기는 조회 조건.
 * 각 필드는 null=해당 종류 전체, 빈 집합=해당 종류 없음, 비어있지 않은 집합=그 안의 것만 —
 * [com.jongsun.runcal.widget.WidgetPreset.calendarIds]의 null/empty 관례와 동일하다.
 *
 * 주의: [com.jongsun.runcal.widget.WidgetPreset.notionDatabaseIds]/
 * [com.jongsun.runcal.data.AppPreset.notionDatabaseIds]는 "null=Notion 없음"(opt-in, 기존
 * 프리셋 보호를 위해 calendarIds와 의도적으로 비대칭)이라, 프리셋을 이 클래스로 변환할 때
 * `notionDbIds = preset.notionDatabaseIds`처럼 그대로 넘기면 안 된다 — 프리셋의 null을
 * emptySet()으로 바꿔서 넘겨야 한다. (Phase 3에서 CalendarViewModel/RunCalWidgetRenderer가
 * 프리셋→SourceSelection 변환을 담당할 때 반드시 지킬 것.)
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
