package com.jongsun.runcal.data

import com.jongsun.runcal.data.source.EventSourceKind

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val color: Int,
    val visible: Boolean,
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
)
