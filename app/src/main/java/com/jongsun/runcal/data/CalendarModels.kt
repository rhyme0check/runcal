package com.jongsun.runcal.data

import com.jongsun.runcal.data.source.EventSourceKind

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val color: Int,
    val visible: Boolean,
    // CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR 여부.
    // 편집 화면의 "소속 캘린더" 선택지를 여기서 걸러서, 쓰기 실패로 이어질 읽기 전용 캘린더를
    // 애초에 고를 수 없게 한다.
    val isWritable: Boolean = true,
)

data class EventItem(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String = "",
    // 이하 4개는 Notion 연동을 위한 신규 필드. 기본값이 있어 기존 호출부는 전부 그대로 컴파일된다.
    val sourceKind: EventSourceKind = EventSourceKind.CALENDAR,
    val notionPageId: String? = null,
    val notionDatabaseId: String? = null,
    val notionStatus: String? = null,
    // P2(편집) 신규 필드.
    val description: String = "",
    // Notion 항목 전용 — 읽기 전용 편집 화면의 "Notion에서 열기" 링크에 쓴다.
    val notionUrl: String? = null,
    // P2-B(반복 일정) 신규 필드. RFC5545 RRULE 원문(예: "FREQ=WEEKLY;BYDAY=MO,WE"). 반복이
    // 아니면 null/빈 문자열 — CalendarContract.Instances는 이 값을 회차마다 그대로 복제해서
    // 돌려주므로, 어떤 회차를 눌러도 같은 반복 규칙을 읽을 수 있다.
    val rrule: String? = null,
)
